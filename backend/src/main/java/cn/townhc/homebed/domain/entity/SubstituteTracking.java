package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.SubstituteFollowUp;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 替代材料执行后的效果追踪记录。
 * 每次后续上门（或医生远程查看伤口照片）登记一次：愈合正常/变慢/过敏，
 * 伤口照片通过 VisitPhoto.substitute 关联，供医生回看替代原因并决定是否调整后续护理材料。
 */
@Getter
@Setter
@Entity
@Table(name = "substitute_tracking")
public class SubstituteTracking extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "substitute_id")
    private MaterialSubstitute substitute;

    /** 登记该次效果的巡诊任务（可空，医生也可依据家属回传照片登记） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubstituteFollowUp outcome;

    @Column(length = 1024)
    private String note;

    /** 本次追踪关联照片数量（照片实体另存） */
    private Integer photoCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorder_id")
    private SysUser recorder;

    private LocalDateTime recordedAt;
}
