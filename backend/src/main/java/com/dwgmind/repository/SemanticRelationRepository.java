package com.dwgmind.repository;

import com.dwgmind.domain.SemanticRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SemanticRelationRepository extends JpaRepository<SemanticRelation, Long> {
    List<SemanticRelation> findByDrawingIdOrderByIdAsc(Long drawingId);
    void deleteByDrawingId(Long drawingId);
}
