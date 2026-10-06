package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.SubstituteTracking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubstituteTrackingRepository extends JpaRepository<SubstituteTracking, Long> {
    List<SubstituteTracking> findBySubstituteIdOrderByRecordedAtAsc(Long substituteId);
}
