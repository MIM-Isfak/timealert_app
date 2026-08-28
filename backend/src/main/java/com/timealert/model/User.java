package com.timealert.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Represents a registered user.
 *
 * Note on Lombok: @Data is intentionally NOT used because it would generate
 * toString() including the password field (even as a bcrypt hash, this is a
 * security risk in logs). We use explicit annotations instead.
 *
 * Note on `demo` field: the field is named `demo` (not `isDemo`) to avoid
 * Lombok generating `isIsDemo()` — a known gotcha with boolean fields whose
 * names start with "is". The column is mapped to `is_demo` explicitly.
 */
@Getter
@Setter
@ToString(exclude = "password")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    private String name;

    // Marks a shared demo/guest account. NotificationService skips real email
    // sending for demo users so public LinkedIn visitors can't trigger real SMTP sends.
    @Column(name = "is_demo", nullable = false)
    private boolean demo = false;
}