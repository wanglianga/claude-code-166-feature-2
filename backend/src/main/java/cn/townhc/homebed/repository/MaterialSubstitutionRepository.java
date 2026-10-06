package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.MaterialSubstitution;
import cn.townhc.homebed.domain.enums.DomainEnums.SubstituteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialSubstitutionRepository extends JpaRepository<MaterialSubstitution, Long> {
    List<MaterialSubstitution> findByPatientIdOrderByCreatedAtDesc(Long patientId);
    List<MaterialSubstitution> findByVisitId(Long visitId);
    List<MaterialSubstitution> findByStatusOrderByCreatedAtDesc(SubstituteStatus status);
    List<MaterialSubstitution> findBySubstituteMaterialIdOrderByCreatedAtDesc(Long materialId);
    List<MaterialSubstitution> findByOriginalMaterialIdOrderByCreatedAtDesc(Long materialId);
    List<MaterialSubstitution> findByRestockTaskId(Long restockTaskId);
    java.util.Optional<MaterialSubstitution> findByOutTransactionId(Long outTransactionId);
}
