package com.haoy.aiapp.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    /**
     * 必须的角色，多个角色用逗号隔开。
     * 如果为空，表示无需角色校验。
     *
     * @return 角色字符串
     */
    String mustRole() default "";
}
