package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期时间解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if(shop == null) {
            return Result.fail("店铺不存在！");
        }
        //7 返回
        return Result.ok(shop);
    }

    @Override
    @Transactional//下面的更新和删除动作目前是单机执行的，所以可以通过transactional控制。分布式则不行
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    private boolean tryLock(String key) {
        //这里设置10秒有效期，是怕万一程序执行一半出现异常，没有释放锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY+ id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson != null) {
            //返回一个错误信息
            return null;
        }
        //4.不存在,根据id查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if(shop == null) {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7 返回
        return shop;
    }
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY+ id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson != null) {
            //返回一个错误信息
            return null;
        }


        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock) {
                //4.3失败 休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4成功 根据id查询数据库
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            //5.不存在，返回错误
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在 写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7 释放互斥锁
            unlock(lockKey);
        }

        //8 返回
        return shop;
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTION = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY+ id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)) {
            //3.不存在 直接返回
            return null;
        }
        //4.命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期 直接返回店铺信息
            return shop;
        }

        //5.2已过期，重建缓存
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取互斥锁成功
        if(isLock) {
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTION.submit(() -> {
               try {
                   this.saveShop2Redis(id,20L);
               } catch (Exception e) {
                   throw new RuntimeException(e);
               } finally {
                   //释放锁
                   unlock(lockKey);
               }
            });
        }

        //6.4返回过期的商铺信息

        //7 返回
        return shop;
    }
}