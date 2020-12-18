package com.tyf.dubbo.impl;

import com.alibaba.dubbo.common.URL;
import com.tyf.dubbo.api.Car;


/**
 * 这个类是由Spring管理的，也可以注入到Drive中，利用的就是SpringExtensionFactory
 *
 *
 */

public class SpringCar implements Car {

    public String getColor(URL url) {
        return "Spring car";
    }
}
