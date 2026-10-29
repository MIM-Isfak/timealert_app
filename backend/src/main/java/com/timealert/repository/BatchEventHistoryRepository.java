package com.timealert.repository;
import com.timealert.model.BatchEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BatchEventHistoryRepository extends JpaRepository<BatchEventHistory, Long> {
    List<BatchEventHistory> findByEventIdOrderByVersionDesc(Long eventId);
}
