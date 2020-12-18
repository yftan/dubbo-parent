package com.tyf.dubbo;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.tyf.dubbo.api.Car;


public class CarTest {
    public static void main(String[] args) {
        ExtensionLoader<Car> loader = ExtensionLoader.getExtensionLoader(Car.class);
        Car red = loader.getExtension("red");
        red.getColor(null);
    }
}
