package com.tyf.dubbo.impl;


import com.alibaba.dubbo.common.URL;
import com.tyf.dubbo.api.Car;

/**
 * 包装类
 *
 * dubbo通过它可以模拟AOP
 *
 * 需要加到配置文件中，并且不用key
 *
 */
public class CarWapper implements Car {
    private Car car;

    public CarWapper(Car car) {
        this.car = car;
    }


    public String getColor(URL url) {
        System.out.println("before");
        System.out.println(car.getColor(null));
        System.out.println("after");
        return null;
    }
}
