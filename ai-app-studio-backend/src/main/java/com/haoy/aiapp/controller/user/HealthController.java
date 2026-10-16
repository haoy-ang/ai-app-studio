package com.haoy.aiapp.controller.user;


import com.haoy.aiapp.common.BaseResponse;
import com.haoy.aiapp.common.ResultUtils;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class HealthController {

    @GetMapping("/")
    public BaseResponse<String> health() {
        return ResultUtils.success("OK");
    }
}
