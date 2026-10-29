package com.timealert.repository;
import com.timealert.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
public interface BatchTimetableEventRepository extends JpaRepository<BatchTimetableEvent, Long> {
    List<BatchTimetableEvent> findByBatchIdOrderByEventDateAscStartTimeAsc(Long batchId);
    List<BatchTimetableEvent> findByBatchIdAndStatusAndEventDateGreaterThanEqualOrderByEventDateAscStartTimeAsc(Long batchId, BatchEventStatus status, LocalDate date);
    boolean existsByBatchIdAndSeriesIdAndEventDateAndStartTimeAndTitle(Long batchId, java.util.UUID seriesId, LocalDate date, LocalTime time, String title);
    boolean existsByBatchIdAndEventDateAndStartTimeAndTitle(Long batchId, LocalDate date, LocalTime time, String title);
}
