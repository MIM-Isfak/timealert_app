package com.timealert.repository;
import com.timealert.model.BatchReminderPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface BatchReminderPreferenceRepository extends JpaRepository<BatchReminderPreference,Long> {
    Optional<BatchReminderPreference> findByUserId(Long userId);
}
