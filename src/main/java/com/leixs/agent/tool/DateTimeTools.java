package com.leixs.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DateTimeTools {

    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";

    //获取**当前请求上下文对应的时区下的当前时间**，返回带时区的 ISO 时间字符串
    @Tool(description = "Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }

}
