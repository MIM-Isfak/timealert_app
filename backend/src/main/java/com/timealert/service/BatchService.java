package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.BatchMembershipRepository;
import com.timealert.repository.BatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BatchService {

    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private BatchMembershipRepository membershipRepository;

    public String generateUniqueInvitationCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!batchRepository.existsByInvitationCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate unique invitation code.");
    }

    @Transactional
    public Batch createBatch(User rep, String name, String academicYear, String semester) {
        if (rep == null || rep.getRole() != Role.REP) {
            throw new SecurityException("Only representatives can create batches.");
        }
        if (name == null || name.trim().length() < 2) {
            throw new IllegalArgumentException("Batch name must be at least 2 characters.");
        }

        Batch batch = new Batch();
        batch.setName(name.trim());
        batch.setAcademicYear(academicYear != null && !academicYear.trim().isEmpty() ? academicYear.trim() : null);
        batch.setSemester(semester != null && !semester.trim().isEmpty() ? semester.trim() : null);
        batch.setActive(true);
        batch.setInvitationCode(generateUniqueInvitationCode());
        batch.setRep(rep);
        batch.setCreatedAt(LocalDateTime.now());

        return batchRepository.save(batch);
    }

    public List<Batch> getBatchesForRep(User rep) {
        if (rep == null || rep.getRole() != Role.REP) {
            throw new SecurityException("Representative role required.");
        }
        return batchRepository.findByRepId(rep.getId());
    }

    public Batch getBatchForUser(User user, Long batchId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found."));

        if (user.getRole() == Role.REP && batch.getRep().getId().equals(user.getId())) {
            return batch;
        }

        if (user.getRole() == Role.STUDENT) {
            boolean isApproved = membershipRepository.existsByBatchIdAndStudentIdAndStatus(batchId, user.getId(), MembershipStatus.APPROVED);
            if (isApproved) {
                return batch;
            }
        }

        throw new SecurityException("Access denied to batch.");
    }

    @Transactional
    public Batch updateBatch(User rep, Long batchId, String name, String academicYear, String semester, Boolean active) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found."));

        if (!batch.getRep().getId().equals(rep.getId())) {
            throw new SecurityException("You do not have permission to manage this batch.");
        }

        if (name != null) {
            if (name.trim().length() < 2) {
                throw new IllegalArgumentException("Batch name must be at least 2 characters.");
            }
            batch.setName(name.trim());
        }
        if (academicYear != null) {
            batch.setAcademicYear(academicYear.trim().isEmpty() ? null : academicYear.trim());
        }
        if (semester != null) {
            batch.setSemester(semester.trim().isEmpty() ? null : semester.trim());
        }
        if (active != null) {
            batch.setActive(active);
        }

        return batchRepository.save(batch);
    }

    public List<BatchMembership> getBatchRequests(User rep, Long batchId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found."));

        if (!batch.getRep().getId().equals(rep.getId())) {
            throw new SecurityException("You do not have permission to access requests for this batch.");
        }

        return membershipRepository.findByBatchId(batchId);
    }

    @Transactional
    public BatchMembership approveRequest(User rep, Long batchId, Long requestId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found."));

        if (!batch.getRep().getId().equals(rep.getId())) {
            throw new SecurityException("You do not have permission to approve requests for this batch.");
        }

        BatchMembership membership = membershipRepository.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Join request not found."));

        if (!membership.getBatch().getId().equals(batchId)) {
            throw new IllegalArgumentException("Request does not belong to this batch.");
        }

        membership.setStatus(MembershipStatus.APPROVED);
        membership.setReviewedAt(LocalDateTime.now());
        return membershipRepository.save(membership);
    }

    @Transactional
    public BatchMembership rejectRequest(User rep, Long batchId, Long requestId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found."));

        if (!batch.getRep().getId().equals(rep.getId())) {
            throw new SecurityException("You do not have permission to reject requests for this batch.");
        }

        BatchMembership membership = membershipRepository.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Join request not found."));

        if (!membership.getBatch().getId().equals(batchId)) {
            throw new IllegalArgumentException("Request does not belong to this batch.");
        }

        membership.setStatus(MembershipStatus.REJECTED);
        membership.setReviewedAt(LocalDateTime.now());
        return membershipRepository.save(membership);
    }

    @Transactional
    public BatchMembership submitJoinRequest(User student, String invitationCode) {
        if (student == null || student.getRole() != Role.STUDENT) {
            throw new SecurityException("Only students can submit batch join requests.");
        }
        if (invitationCode == null || invitationCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Invitation code is required.");
        }

        String normalizedCode = invitationCode.trim().toUpperCase();
        Batch batch = batchRepository.findByInvitationCode(normalizedCode)
                .orElseThrow(() -> new NoSuchElementException("Invalid invitation code."));

        if (!batch.isActive()) {
            throw new IllegalStateException("This batch is no longer active or accepting new members.");
        }

        Optional<BatchMembership> existing = membershipRepository.findByBatchIdAndStudentId(batch.getId(), student.getId());
        if (existing.isPresent()) {
            BatchMembership m = existing.get();
            if (m.getStatus() == MembershipStatus.APPROVED) {
                throw new IllegalStateException("You are already an approved member of this batch.");
            }
            if (m.getStatus() == MembershipStatus.PENDING) {
                throw new IllegalStateException("You already have a pending join request for this batch.");
            }
            m.setStatus(MembershipStatus.PENDING);
            m.setRequestedAt(LocalDateTime.now());
            m.setReviewedAt(null);
            return membershipRepository.save(m);
        }

        BatchMembership membership = new BatchMembership();
        membership.setBatch(batch);
        membership.setStudent(student);
        membership.setStatus(MembershipStatus.PENDING);
        membership.setRequestedAt(LocalDateTime.now());

        return membershipRepository.save(membership);
    }

    public List<BatchMembership> getStudentMemberships(User student) {
        if (student == null || student.getRole() != Role.STUDENT) {
            throw new SecurityException("Only students can view student memberships.");
        }
        return membershipRepository.findByStudentId(student.getId());
    }

    public long getApprovedMemberCount(Long batchId) {
        return membershipRepository.countByBatchIdAndStatus(batchId, MembershipStatus.APPROVED);
    }

    public long getPendingRequestCount(Long batchId) {
        return membershipRepository.countByBatchIdAndStatus(batchId, MembershipStatus.PENDING);
    }
}
