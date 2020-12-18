package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.*;

@Activate(group = Constants.PROVIDER)
public class TestActivity implements Filter {
    public String test(){
        return "This is a test method";
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        return null;
    }
}
