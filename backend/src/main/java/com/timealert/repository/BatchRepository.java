package com.timealert.repository;

import com.timealert.model.Batch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BatchRepository extends JpaRepository<Batch, Long> {
    Optional<Batch> findByInvitationCode(String invitationCode);
    boolean existsByInvitationCode(String invitationCode);
    List<Batch> findByRepId(Long repId);
    Optional<Batch> findByIdAndRepId(Long id, Long repId);
}
