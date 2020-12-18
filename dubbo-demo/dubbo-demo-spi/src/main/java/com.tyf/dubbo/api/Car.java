package com.tyf.dubbo.api;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

@SPI
public interface Car {

    @Adaptive("carType")
    String getColor(URL url);
}
