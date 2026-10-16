package com.neuringo.neuringobe.user.domain;

/**
 * 계정 상태. 정지·초대를 할 운영자가 없고 탈퇴는 계정 행을 지우므로 {@code ACTIVE} 하나만 쓴다. 정지 기능이 생기면 값과 V11 의 DB 제약을 함께 넓힌다.
 */
public enum AccountStatus {
    ACTIVE
}
