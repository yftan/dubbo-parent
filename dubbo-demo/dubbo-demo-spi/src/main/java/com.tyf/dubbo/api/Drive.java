package com.tyf.dubbo.api;


import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * dubbo模拟IOC
 *
 * 主要通过URL完成依赖注入
 *
 */
@SPI
public interface Drive {
    void drive(URL url);
}
