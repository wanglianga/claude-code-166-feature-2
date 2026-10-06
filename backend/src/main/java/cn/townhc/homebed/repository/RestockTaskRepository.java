package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.RestockTask;
import cn.townhc.homebed.domain.enums.DomainEnums.RestockStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestockTaskRepository extends JpaRepository<RestockTask, Long> {
    List<RestockTask> findByStatusOrderByUrgentDescCreatedAtDesc(RestockStatus status);
    List<RestockTask> findAllByOrderByCreatedAtDesc();
    List<RestockTask> findByMaterialIdAndStatusOrderByCreatedAtDesc(Long materialId, RestockStatus status);
}
