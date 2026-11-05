package com.dwgmind.repository;

import com.dwgmind.domain.Drawing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DrawingRepository extends JpaRepository<Drawing, Long> {
    Optional<Drawing> findFirstBySha256OrderByIdDesc(String sha256);
    List<Drawing> findAllByOrderByIdDesc();
}
