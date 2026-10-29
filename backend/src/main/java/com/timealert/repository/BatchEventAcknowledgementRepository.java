package com.timealert.repository;
import com.timealert.model.BatchEventAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BatchEventAcknowledgementRepository extends JpaRepository<BatchEventAcknowledgement, Long> {
    java.util.Optional<BatchEventAcknowledgement> findByEventIdAndStudentId(Long eventId, Long studentId);
    long countByEventIdAndVersion(Long eventId, int version);
    boolean existsByEventIdAndStudentIdAndVersion(Long eventId, Long studentId, int version);
}
