package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.WoundFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WoundFollowUpRepository extends JpaRepository<WoundFollowUp, Long> {
    List<WoundFollowUp> findBySubstitutionIdOrderByObservedAtAsc(Long substitutionId);
    List<WoundFollowUp> findByPatientIdOrderByObservedAtDesc(Long patientId);
    List<WoundFollowUp> findByVisitId(Long visitId);
}
