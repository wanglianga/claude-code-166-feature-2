package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.WoundOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 替代耗材执行后的伤口效果随访。
 * 后续每次上门上传伤口照片时记录愈合趋势；出现愈合变慢/过敏时自动生成风险事件，
 * 医生可据此回看替代原因（关联替代确认单）并调整之后的护理材料。
 */
@Getter
@Setter
@Entity
@Table(name = "wound_follow_up")
public class WoundFollowUp extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "substitution_id")
    private MaterialSubstitution substitution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    /** 本次观察所依据的巡诊任务（后续上门） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    /** 伤口照片路径（可多张，逗号分隔） */
    @Column(length = 1024)
    private String photoPaths;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WoundOutcome outcome;

    /** 创面面积 cm²（可选，用于趋势对比） */
    private Double woundArea;

    /** 渗液/红肿/瘙痒等观察描述 */
    @Column(length = 1024)
    private String observation;

    @Column(length = 512)
    private String nurseNote;

    /** 记录人（随访护士） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorder_id")
    private SysUser recorder;

    private LocalDateTime observedAt;
}
