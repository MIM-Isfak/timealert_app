package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.BatchMembershipRepository;
import com.timealert.repository.BatchRepository;
import com.timealert.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchServiceTests {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private BatchMembershipRepository membershipRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BatchService batchService;

    @InjectMocks
    private OwnerBootstrapService ownerBootstrapService;

    private User rep1;
    private User rep2;
    private User student1;
    private Batch batch1;
    private Batch batch2;

    @BeforeEach
    void setUp() {
        rep1 = new User();
        rep1.setId(10L);
        rep1.setEmail("rep1@example.com");
        rep1.setName("Rep One");
        rep1.setRole(Role.REP);

        rep2 = new User();
        rep2.setId(20L);
        rep2.setEmail("rep2@example.com");
        rep2.setName("Rep Two");
        rep2.setRole(Role.REP);

        student1 = new User();
        student1.setId(100L);
        student1.setEmail("student1@example.com");
        student1.setName("Student One");
        student1.setRole(Role.STUDENT);

        batch1 = new Batch();
        batch1.setId(501L);
        batch1.setName("Computer Science 2026");
        batch1.setAcademicYear("2025/2026");
        batch1.setSemester("Semester 1");
        batch1.setActive(true);
        batch1.setInvitationCode("CODE501A");
        batch1.setRep(rep1);

        batch2 = new Batch();
        batch2.setId(502L);
        batch2.setName("Data Science 2026");
        batch2.setActive(true);
        batch2.setInvitationCode("CODE502B");
        batch2.setRep(rep2);
    }

    @Test
    void repCanCreateBatchWithUniqueInvitationCode() {
        when(batchRepository.existsByInvitationCode(anyString())).thenReturn(false);
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> {
            Batch b = inv.getArgument(0);
            b.setId(777L);
            return b;
        });

        Batch created = batchService.createBatch(rep1, "Civil Engineering 2026", "Year 2", "Sem 1");

        assertNotNull(created);
        assertEquals(777L, created.getId());
        assertEquals("Civil Engineering 2026", created.getName());
        assertEquals("Year 2", created.getAcademicYear());
        assertEquals("Sem 1", created.getSemester());
        assertTrue(created.isActive());
        assertEquals(rep1, created.getRep());
        assertNotNull(created.getInvitationCode());
        assertEquals(8, created.getInvitationCode().length());
    }

    @Test
    void studentCannotCreateBatch() {
        assertThrows(SecurityException.class, () ->
                batchService.createBatch(student1, "Unauthorized Batch", "Year 1", "Sem 1"));
        verifyNoInteractions(batchRepository);
    }

    @Test
    void batchNameMustBeAtLeastTwoCharacters() {
        assertThrows(IllegalArgumentException.class, () ->
                batchService.createBatch(rep1, " ", "Year 1", "Sem 1"));
    }

    @Test
    void repCanUpdateOnlyTheirOwnBatch() {
        when(batchRepository.findById(501L)).thenReturn(Optional.of(batch1));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));

        Batch updated = batchService.updateBatch(rep1, 501L, "CS 2026 Renamed", "Year 3", "Sem 2", false);

        assertEquals("CS 2026 Renamed", updated.getName());
        assertEquals("Year 3", updated.getAcademicYear());
        assertEquals("Sem 2", updated.getSemester());
        assertFalse(updated.isActive());
    }

    @Test
    void differentRepCannotUpdateBatchTheyDoNotOwn() {
        when(batchRepository.findById(501L)).thenReturn(Optional.of(batch1));

        SecurityException ex = assertThrows(SecurityException.class, () ->
                batchService.updateBatch(rep2, 501L, "Hacked Name", null, null, null));

        assertTrue(ex.getMessage().contains("permission"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void studentCanSubmitJoinRequestWithValidCode() {
        when(batchRepository.findByInvitationCode("CODE501A")).thenReturn(Optional.of(batch1));
        when(membershipRepository.findByBatchIdAndStudentId(501L, 100L)).thenReturn(Optional.empty());
        when(membershipRepository.save(any(BatchMembership.class))).thenAnswer(inv -> {
            BatchMembership m = inv.getArgument(0);
            m.setId(901L);
            return m;
        });

        BatchMembership req = batchService.submitJoinRequest(student1, "code501a");

        assertNotNull(req);
        assertEquals(MembershipStatus.PENDING, req.getStatus());
        assertEquals(batch1, req.getBatch());
        assertEquals(student1, req.getStudent());
    }

    @Test
    void nonStudentCannotSubmitJoinRequest() {
        assertThrows(SecurityException.class, () ->
                batchService.submitJoinRequest(rep1, "CODE501A"));
    }

    @Test
    void joinRequestFailsForInactiveBatch() {
        batch1.setActive(false);
        when(batchRepository.findByInvitationCode("CODE501A")).thenReturn(Optional.of(batch1));

        assertThrows(IllegalStateException.class, () ->
                batchService.submitJoinRequest(student1, "CODE501A"));
    }

    @Test
    void joinRequestFailsIfAlreadyApproved() {
        BatchMembership existing = new BatchMembership();
        existing.setBatch(batch1);
        existing.setStudent(student1);
        existing.setStatus(MembershipStatus.APPROVED);

        when(batchRepository.findByInvitationCode("CODE501A")).thenReturn(Optional.of(batch1));
        when(membershipRepository.findByBatchIdAndStudentId(501L, 100L)).thenReturn(Optional.of(existing));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                batchService.submitJoinRequest(student1, "CODE501A"));
        assertTrue(ex.getMessage().contains("already an approved member"));
    }

    @Test
    void joinRequestFailsIfAlreadyPending() {
        BatchMembership existing = new BatchMembership();
        existing.setBatch(batch1);
        existing.setStudent(student1);
        existing.setStatus(MembershipStatus.PENDING);

        when(batchRepository.findByInvitationCode("CODE501A")).thenReturn(Optional.of(batch1));
        when(membershipRepository.findByBatchIdAndStudentId(501L, 100L)).thenReturn(Optional.of(existing));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                batchService.submitJoinRequest(student1, "CODE501A"));
        assertTrue(ex.getMessage().contains("already have a pending join request"));
    }

    @Test
    void joinRequestCanBeResubmittedIfPreviouslyRejected() {
        BatchMembership existing = new BatchMembership();
        existing.setBatch(batch1);
        existing.setStudent(student1);
        existing.setStatus(MembershipStatus.REJECTED);

        when(batchRepository.findByInvitationCode("CODE501A")).thenReturn(Optional.of(batch1));
        when(membershipRepository.findByBatchIdAndStudentId(501L, 100L)).thenReturn(Optional.of(existing));
        when(membershipRepository.save(existing)).thenReturn(existing);

        BatchMembership resubmitted = batchService.submitJoinRequest(student1, "CODE501A");

        assertEquals(MembershipStatus.PENDING, resubmitted.getStatus());
        assertNull(resubmitted.getReviewedAt());
        verify(membershipRepository).save(existing);
    }

    @Test
    void owningRepCanApproveRequest() {
        BatchMembership request = new BatchMembership();
        request.setId(901L);
        request.setBatch(batch1);
        request.setStudent(student1);
        request.setStatus(MembershipStatus.PENDING);

        when(batchRepository.findById(501L)).thenReturn(Optional.of(batch1));
        when(membershipRepository.findById(901L)).thenReturn(Optional.of(request));
        when(membershipRepository.save(any(BatchMembership.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchMembership approved = batchService.approveRequest(rep1, 501L, 901L);

        assertEquals(MembershipStatus.APPROVED, approved.getStatus());
        assertNotNull(approved.getReviewedAt());
    }

    @Test
    void nonOwningRepCannotApproveRequest() {
        when(batchRepository.findById(501L)).thenReturn(Optional.of(batch1));

        assertThrows(SecurityException.class, () ->
                batchService.approveRequest(rep2, 501L, 901L));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void crossBatchApprovalAttemptIsRejected() {
        // Request belongs to batch2, but caller specifies batch1!
        BatchMembership requestForBatch2 = new BatchMembership();
        requestForBatch2.setId(902L);
        requestForBatch2.setBatch(batch2);
        requestForBatch2.setStudent(student1);
        requestForBatch2.setStatus(MembershipStatus.PENDING);

        when(batchRepository.findById(501L)).thenReturn(Optional.of(batch1));
        when(membershipRepository.findById(902L)).thenReturn(Optional.of(requestForBatch2));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                batchService.approveRequest(rep1, 501L, 902L));

        assertTrue(ex.getMessage().contains("does not belong to this batch"));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void crossBatchRejectionAttemptIsRejected() {
        BatchMembership requestForBatch2 = new BatchMembership();
        requestForBatch2.setId(902L);
        requestForBatch2.setBatch(batch2);
        requestForBatch2.setStudent(student1);
        requestForBatch2.setStatus(MembershipStatus.PENDING);

        when(batchRepository.findById(501L)).thenReturn(Optional.of(batch1));
        when(membershipRepository.findById(902L)).thenReturn(Optional.of(requestForBatch2));

        assertThrows(IllegalArgumentException.class, () ->
                batchService.rejectRequest(rep1, 501L, 902L));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void ownerBootstrapParsesEmailsAndUpgradesRoles() {
        ReflectionTestUtils.setField(ownerBootstrapService, "ownerEmailsConfig", "owner1@example.com, owner2@example.com ");

        User existingUser = new User();
        existingUser.setId(99L);
        existingUser.setEmail("owner1@example.com");
        existingUser.setRole(Role.STUDENT);

        when(userRepository.findByEmail("owner1@example.com")).thenReturn(Optional.of(existingUser));
        when(userRepository.findByEmail("owner2@example.com")).thenReturn(Optional.empty());

        ownerBootstrapService.bootstrapOwners();

        assertEquals(Role.OWNER, existingUser.getRole());
        verify(userRepository).save(existingUser);
    }
}
