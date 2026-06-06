package com.timealert.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 512)
    private String endpoint;

    private String p256dh;
    private String auth;
}