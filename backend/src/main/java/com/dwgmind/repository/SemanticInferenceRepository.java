package com.dwgmind.repository;

import com.dwgmind.domain.SemanticInference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SemanticInferenceRepository extends JpaRepository<SemanticInference, Long> {
    List<SemanticInference> findByDrawingIdOrderByIdAsc(Long drawingId);
    List<SemanticInference> findByDrawingIdAndReviewStatus(Long drawingId, String reviewStatus);
    long countByDrawingId(Long drawingId);
    void deleteByDrawingId(Long drawingId);
}
