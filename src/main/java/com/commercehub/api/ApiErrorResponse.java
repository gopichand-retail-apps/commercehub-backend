package com.commercehub.api;

public record ApiErrorResponse(String code, String message, String requestId) {}
