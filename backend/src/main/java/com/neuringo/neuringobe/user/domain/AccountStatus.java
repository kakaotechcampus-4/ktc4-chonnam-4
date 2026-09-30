package com.neuringo.neuringobe.user.domain;

/** 정본 ERD 의 계정 상태. S1 은 가입 시 {@code ACTIVE} 만 쓰며, 상태 전이는 후속 스프린트에서 만든다. */
public enum AccountStatus {
    INVITED,
    ACTIVE,
    SUSPENDED,
    WITHDRAWN
}
