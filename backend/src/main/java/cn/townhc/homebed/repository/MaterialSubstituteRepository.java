package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.MaterialSubstitute;
import cn.townhc.homebed.domain.enums.DomainEnums.SubstituteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialSubstituteRepository extends JpaRepository<MaterialSubstitute, Long> {
    List<MaterialSubstitute> findByPatientIdOrderByCreatedAtDesc(Long patientId);
    List<MaterialSubstitute> findByVisitId(Long visitId);
    List<MaterialSubstitute> findByStatusOrderByCreatedAtDesc(SubstituteStatus status);
    List<MaterialSubstitute> findAllByOrderByCreatedAtDesc();
    long countByStatus(SubstituteStatus status);
}
