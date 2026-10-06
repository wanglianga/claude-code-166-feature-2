package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.MaterialReplenishTask;
import cn.townhc.homebed.domain.enums.DomainEnums.ReplenishStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialReplenishTaskRepository extends JpaRepository<MaterialReplenishTask, Long> {
    List<MaterialReplenishTask> findByStatusOrderByCreatedAtDesc(ReplenishStatus status);
    List<MaterialReplenishTask> findByPatientIdOrderByCreatedAtDesc(Long patientId);
    List<MaterialReplenishTask> findAllByOrderByCreatedAtDesc();
    long countByStatus(ReplenishStatus status);
}
