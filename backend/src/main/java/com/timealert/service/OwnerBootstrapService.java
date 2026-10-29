package com.timealert.service;

import com.timealert.model.Role;
import com.timealert.model.User;
import com.timealert.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OwnerBootstrapService {

    private static final Logger logger = LoggerFactory.getLogger(OwnerBootstrapService.class);

    @Value("${owner.emails:}")
    private String ownerEmailsConfig;

    @Autowired
    private UserRepository userRepository;

    public Set<String> getOwnerEmails() {
        if (ownerEmailsConfig == null || ownerEmailsConfig.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(ownerEmailsConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean isConfiguredOwner(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        return getOwnerEmails().contains(email.trim().toLowerCase());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapOwners() {
        Set<String> emails = getOwnerEmails();
        if (emails.isEmpty()) {
            return;
        }
        int upgraded = 0;
        for (String email : emails) {
            var userOpt = userRepository.findByEmail(email);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                if (user.getRole() != Role.OWNER) {
                    user.setRole(Role.OWNER);
                    userRepository.save(user);
                    upgraded++;
                }
            }
        }
        if (upgraded > 0) {
            logger.info("Environment bootstrap: upgraded {} account(s) to OWNER role.", upgraded);
        }
    }

    public void syncOwnerRoleIfConfigured(User user) {
        if (user != null && user.getEmail() != null && isConfiguredOwner(user.getEmail())) {
            if (user.getRole() != Role.OWNER) {
                user.setRole(Role.OWNER);
                userRepository.save(user);
            }
        }
    }
}
