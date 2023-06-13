package com.hmdp.utils;

public interface DistributedLock {
    /**
     * 尝试获取锁
     * @param timeoutSeconds 锁的超时时间，过期后自动释放
     * @return true 代表获取锁成功；false 代表获取锁失败
     */
    boolean tryLock(long timeoutSeconds);

    /**
     * 释放锁
     */
    void unlock();
}
