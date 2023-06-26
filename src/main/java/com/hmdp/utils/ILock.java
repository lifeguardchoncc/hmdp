package com.hmdp.utils;

/**
 * @description:
 * @author: lyl
 * @time: 2023/6/25 11:59
 */
public interface ILock {
     boolean trylock(long timeoutsec);
    void unlock();

}
