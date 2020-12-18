package com.tyf.dubbo.impl;

import com.alibaba.dubbo.common.URL;
import com.tyf.dubbo.api.Car;

public class RedCar implements Car {
    public String getColor(URL url) {
        return "Red";
    }
}
