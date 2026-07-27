package com.compact.crm.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ApiError {

    private String timestamp;
    private int status;
    private String error;
    private String message;
}