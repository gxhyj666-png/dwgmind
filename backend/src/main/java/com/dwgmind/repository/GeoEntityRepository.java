package com.dwgmind.repository;

import com.dwgmind.domain.GeoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GeoEntityRepository extends JpaRepository<GeoEntity, Long> {
    List<GeoEntity> findByDrawingIdOrderByIdAsc(Long drawingId);
    long countByDrawingId(Long drawingId);
    void deleteByDrawingId(Long drawingId);
}
