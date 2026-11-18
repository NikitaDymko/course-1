package io.github.mitohondriyaa.order.dto;

public record UserDetailsResponse(
    String email,
    String firstName,
    String lastName,
    String phone
) {}
