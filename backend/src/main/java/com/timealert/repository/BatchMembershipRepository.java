package com.timealert.repository;

import com.timealert.model.BatchMembership;
import com.timealert.model.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BatchMembershipRepository extends JpaRepository<BatchMembership, Long> {
    Optional<BatchMembership> findByBatchIdAndStudentId(Long batchId, Long studentId);
    List<BatchMembership> findByBatchId(Long batchId);
    List<BatchMembership> findByBatchIdAndStatus(Long batchId, MembershipStatus status);
    List<BatchMembership> findByStudentId(Long studentId);
    List<BatchMembership> findByStudentIdAndStatus(Long studentId, MembershipStatus status);
    boolean existsByBatchIdAndStudentIdAndStatus(Long batchId, Long studentId, MembershipStatus status);
    long countByBatchIdAndStatus(Long batchId, MembershipStatus status);
}
