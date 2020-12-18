package com.tyf.dubbo;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.tyf.dubbo.api.Car;
import com.tyf.dubbo.api.Drive;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.HashMap;
import java.util.Map;

public class DirveTest {
    public static void main(String[] args) {
        ExtensionLoader<Drive> loader = ExtensionLoader.getExtensionLoader(Drive.class);
        Drive drive = loader.getExtension("trucker");
        Map<String, String> map = new HashMap<String, String>();


        // 根据类型不同就完成了注入
        map.put("carType", "blue");
        URL url = new URL("","", 0, map);
        drive.drive(url);
    }
}
