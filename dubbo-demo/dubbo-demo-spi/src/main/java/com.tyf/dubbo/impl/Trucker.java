package com.tyf.dubbo.impl;

import com.alibaba.dubbo.common.URL;
import com.tyf.dubbo.api.Car;
import com.tyf.dubbo.api.Drive;

public class Trucker implements Drive {

    /**
     * 这个car注入的实际上是代理对象
     *
     * package com.tyf.dubbo.api;
     * import com.alibaba.dubbo.common.extension.ExtensionLoader;
     * public class Car$Adaptive implements com.tyf.dubbo.api.Car {
     *     public java.lang.String getColor(com.alibaba.dubbo.common.URL arg0) {
     *         if (arg0 == null) throw new IllegalArgumentException("url == null");
     *         com.alibaba.dubbo.common.URL url = arg0;
     *         // 这个代理对象可以通过URL去实例化，并且还可以结合@SPI注解使用
     *         String extName = url.getParameter("carType");
     *         if (extName == null) throw new IllegalStateException("Fail to get extension(com.tyf.dubbo.api.Car) name from url(" + url.toString() + ") use keys([carType])");
     *         com.tyf.dubbo.api.Car extension = (com.tyf.dubbo.api.Car) ExtensionLoader.getExtensionLoader(com.tyf.dubbo.api.Car.class).getExtension(extName);
     *         return extension.getColor(arg0);
     *     }
     * }
     */
    private Car car;

    // 这个是通过SPIExtensionFactory注入的
//    public void setCar(Car car) {
//        this.car = car;
//    }

    // 这个是通过SpringExtensionFactory注入的
    public void setSpringCar(Car car) {
        this.car = car;
    }

    public void drive(URL url) {
        System.out.println("卡车司机在开：" +  car.getColor(url));
    }
}
