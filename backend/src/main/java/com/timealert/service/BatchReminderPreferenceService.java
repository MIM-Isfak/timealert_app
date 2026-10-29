package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.BatchReminderPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BatchReminderPreferenceService {
    private final BatchReminderPreferenceRepository repository;
    private final BatchReminderSyncService sync;
    public BatchReminderPreferenceService(BatchReminderPreferenceRepository repository,BatchReminderSyncService sync){this.repository=repository;this.sync=sync;}
    private void ensureStudent(User user){if(user==null||user.getRole()!=Role.STUDENT)throw new SecurityException("Student account required.");}
    private BatchReminderPreference getOrCreate(User user){return repository.findByUserId(user.getId()).orElseGet(()->{BatchReminderPreference p=new BatchReminderPreference();p.setUser(user);return repository.save(p);});}
    private Map<String,Object> map(BatchReminderPreference p){Map<String,Object> m=new LinkedHashMap<>();m.put("fiveMinutes",p.isFiveMinutes());m.put("tenMinutes",p.isTenMinutes());m.put("thirtyMinutes",p.isThirtyMinutes());m.put("examDayBefore",p.isExamDayBefore());m.put("notificationSetupPending",p.isNotificationSetupPending());m.put("notificationSyncStatus",p.isNotificationSetupPending()?"PENDING":"READY");m.put("updatedAt",p.getUpdatedAt().toString());return m;}
    @Transactional public Map<String,Object> get(User user){ensureStudent(user);BatchReminderPreference p=getOrCreate(user);p.setNotificationSetupPending(!sync.syncPreferences(user));return map(repository.save(p));}
    @Transactional public Map<String,Object> update(User user,Map<String,Object> body){
        ensureStudent(user);Set<String> keys=Set.of("fiveMinutes","tenMinutes","thirtyMinutes","examDayBefore");
        if(body==null||!body.keySet().equals(keys)||body.values().stream().anyMatch(v->!(v instanceof Boolean)))throw new IllegalArgumentException("Provide boolean values for all four reminder preferences.");
        BatchReminderPreference p=getOrCreate(user);p.setFiveMinutes((Boolean)body.get("fiveMinutes"));p.setTenMinutes((Boolean)body.get("tenMinutes"));p.setThirtyMinutes((Boolean)body.get("thirtyMinutes"));p.setExamDayBefore((Boolean)body.get("examDayBefore"));p.setUpdatedAt(LocalDateTime.now());
        repository.save(p);p.setNotificationSetupPending(!sync.syncPreferences(user));return map(repository.save(p));
    }
}
