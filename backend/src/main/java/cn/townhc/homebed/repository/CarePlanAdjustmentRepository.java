package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.CarePlanAdjustment;
import cn.townhc.homebed.domain.enums.DomainEnums.CareAdjustStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CarePlanAdjustmentRepository extends JpaRepository<CarePlanAdjustment, Long> {
    List<CarePlanAdjustment> findByPatientIdOrderByCreatedAtDesc(Long patientId);
    List<CarePlanAdjustment> findByStatusOrderByCreatedAtDesc(CareAdjustStatus status);
    List<CarePlanAdjustment> findAllByOrderByCreatedAtDesc();
}
