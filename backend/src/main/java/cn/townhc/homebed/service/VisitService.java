package cn.townhc.homebed.service;

import cn.townhc.homebed.common.BusinessException;
import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.domain.enums.DomainEnums.*;
import cn.townhc.homebed.repository.*;
import cn.townhc.homebed.security.LoginUser;
import cn.townhc.homebed.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VisitService {

    private final VisitRepository visitRepository;
    private final VisitCareResultRepository careResultRepository;
    private final VisitPhotoRepository photoRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final RiskEventRepository riskEventRepository;
    private final MaterialRepository materialRepository;
    private final MaterialTransactionRepository materialTransactionRepository;
    private final MaterialSubstitutionRepository substitutionRepository;
    private final ChargeCatalogRepository catalogRepository;
    private final SysUserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Visit> list(String status, Long nurseId, Long patientId) {
        LoginUser me = SecurityUtils.current();
        List<Visit> list;
        if (me.role() == Role.NURSE) {
            if (status != null && !status.isBlank()) {
                list = visitRepository.findByNurseIdAndStatus(me.id(), VisitStatus.valueOf(status));
            } else {
                list = visitRepository.findAll().stream()
                        .filter(v -> v.getNurse() != null && v.getNurse().getId().equals(me.id()))
                        .sorted(Comparator.comparing(Visit::getScheduledAt))
                        .toList();
                return list;
            }
        } else if (status != null && !status.isBlank()) {
            list = visitRepository.findByStatusOrderByScheduledAtAsc(VisitStatus.valueOf(status));
        } else if (patientId != null) {
            list = visitRepository.findByPatientIdOrderByScheduledAtDesc(patientId);
        } else {
            list = visitRepository.findAll();
            list.sort(Comparator.comparing(Visit::getScheduledAt));
            return list;
        }
        list = new ArrayList<>(list);
        list.sort(Comparator.comparing(Visit::getScheduledAt));
        return list;
    }

    @Transactional(readOnly = true)
    public Visit get(Long id) {
        return visitRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "巡诊任务不存在"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Visit visit = get(id);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("visit", visit);
        map.put("careResults", careResultRepository.findByVisitId(id));
        map.put("photos", photoRepository.findByVisitId(id));
        map.put("exceptions", exceptionRepository.findByVisitId(id));
        map.put("materialTransactions", materialTransactionRepository.findByVisitId(id));
        map.put("substitutions", substitutionRepository.findByVisitId(id));
        return map;
    }

    @Transactional
    public Visit start(Long visitId, String locationSnapshot) {
        LoginUser me = SecurityUtils.current();
        Visit visit = get(visitId);
        if (me.role() == Role.NURSE && (visit.getNurse() == null
                || !visit.getNurse().getId().equals(me.id()))) {
            throw new BusinessException(403, "该任务不属于当前护士");
        }
        if (visit.getStatus() != VisitStatus.SCHEDULED) {
            throw new BusinessException("仅已排班任务可签到，当前状态：" + visit.getStatus().label);
        }
        visit.setStatus(VisitStatus.IN_PROGRESS);
        visit.setStartedAt(LocalDateTime.now());
        visit.setNurseLocationSnapshot(locationSnapshot);
        visit = visitRepository.save(visit);
        auditService.log(me, "巡诊任务", "护士签到上门", visit.getId(), visit.getVisitNo(),
                visit.getPatient().getId(), "签到位置：" + locationSnapshot);
        return visit;
    }

    public record MaterialUsage(Long materialId, Integer quantity, String batchNo,
                                Double coldChainTemp, String note) {}

    public record CareResultInput(Long catalogId, Integer quantity,
                                  CareResultStatus resultStatus, String note) {}

    public record SubmitCommand(
            Integer systolicPressure, Integer diastolicPressure, Integer heartRate,
            Double temperature, Integer bloodGlucose, Integer oxygenSaturation, Integer breathRate,
            String pressureUlcerNote, String dressingNote, String catheterNote,
            String medicationNote, String overallNote,
            String familySignName, String familySignPath,
            List<CareResultInput> careResults,
            List<MaterialUsage> materialUsages,
            List<String> photoPaths,
            String locationSnapshot
    ) {}

    @Transactional
    public Visit complete(Long visitId, SubmitCommand cmd) {
        LoginUser me = SecurityUtils.current();
        Visit visit = get(visitId);
        if (me.role() == Role.NURSE && (visit.getNurse() == null
                || !visit.getNurse().getId().equals(me.id()))) {
            throw new BusinessException(403, "该任务不属于当前护士");
        }
        if (visit.getStatus() != VisitStatus.IN_PROGRESS && visit.getStatus() != VisitStatus.SCHEDULED) {
            throw new BusinessException("当前状态[" + visit.getStatus().label + "]不可提交巡诊记录");
        }
        if (cmd.familySignName() == null || cmd.familySignName().isBlank()
                || cmd.familySignPath() == null || cmd.familySignPath().isBlank()) {
            throw new BusinessException("必须取得家属签字（签名图片与签字人姓名）后方可提交");
        }

        // 体征
        visit.setSystolicPressure(cmd.systolicPressure());
        visit.setDiastolicPressure(cmd.diastolicPressure());
        visit.setHeartRate(cmd.heartRate());
        visit.setTemperature(cmd.temperature());
        visit.setBloodGlucose(cmd.bloodGlucose());
        visit.setOxygenSaturation(cmd.oxygenSaturation());
        visit.setBreathRate(cmd.breathRate());
        visit.setPressureUlcerNote(cmd.pressureUlcerNote());
        visit.setDressingNote(cmd.dressingNote());
        visit.setCatheterNote(cmd.catheterNote());
        visit.setMedicationNote(cmd.medicationNote());
        visit.setOverallNote(cmd.overallNote());
        visit.setFamilySignName(cmd.familySignName());
        visit.setFamilySignPath(cmd.familySignPath());
        visit.setFamilySignedAt(LocalDateTime.now());
        if (cmd.locationSnapshot() != null) visit.setNurseLocationSnapshot(cmd.locationSnapshot());
        if (visit.getStartedAt() == null) visit.setStartedAt(LocalDateTime.now().minusMinutes(
                visit.getEstimatedTravelMinutes() == null ? 30 : visit.getEstimatedTravelMinutes()));
        LocalDateTime completedAt = LocalDateTime.now();
        visit.setCompletedAt(completedAt);
        if (visit.getStartedAt() != null) {
            long minutes = java.time.Duration.between(visit.getScheduledAt(), completedAt).toMinutes();
            // 超过预约时间 45 分钟视为上门超时（供管理监控）
            visit.setActualTravelMinutes((int) Math.max(0, minutes));
        }
        visit.setStatus(VisitStatus.COMPLETED);
        visit = visitRepository.save(visit);

        // 护理项目执行记录（与收费目录对应）
        if (cmd.careResults() != null) {
            for (CareResultInput in : cmd.careResults()) {
                ChargeCatalog catalog = in.catalogId() == null ? null
                        : catalogRepository.findById(in.catalogId()).orElse(null);
                VisitCareResult r = new VisitCareResult();
                r.setVisit(visit);
                r.setCatalog(catalog);
                r.setItemName(catalog != null ? catalog.getName() : "护理项目");
                r.setItemCode(catalog != null ? catalog.getCode() : "ITEM");
                r.setUnitPrice(catalog != null ? catalog.getUnitPrice()
                        : java.math.BigDecimal.ZERO);
                r.setQuantity(in.quantity() == null ? 1 : in.quantity());
                r.setResultStatus(in.resultStatus() == null ? CareResultStatus.DONE : in.resultStatus());
                r.setNote(in.note());
                careResultRepository.save(r);
            }
        }

        // 耗材领用（含冷链药品交接温度），扣库存并写出库流水
        if (cmd.materialUsages() != null) {
            for (MaterialUsage u : cmd.materialUsages()) {
                Material m = materialRepository.findById(u.materialId())
                        .orElseThrow(() -> new BusinessException(404, "耗材不存在:" + u.materialId()));
                int qty = u.quantity() == null ? 1 : u.quantity();
                if (m.getStockQty() == null || m.getStockQty() < qty) {
                    throw new BusinessException("耗材[" + m.getName() + "]库存不足，当前库存 "
                            + m.getStockQty() + "，无法领用 " + qty
                            + "。请先登记“耗材缺货”异常，本次任务可挂起改期");
                }
                m.setStockQty(m.getStockQty() - qty);
                materialRepository.save(m);

                MaterialTransaction tx = new MaterialTransaction();
                tx.setMaterial(m);
                tx.setType(MaterialTxType.OUT);
                tx.setQuantity(qty);
                tx.setVisit(visit);
                tx.setOperator(userRepository.findById(me.id()).orElse(null));
                tx.setBatchNo(u.batchNo());
                tx.setColdChainTemp(u.coldChainTemp());
                tx.setNote(u.note());
                tx.setOccurredAt(LocalDateTime.now());
                materialTransactionRepository.save(tx);

                // 冷链交接温度越界 → 风险事件
                if (Boolean.TRUE.equals(m.getColdChain()) && u.coldChainTemp() != null
                        && (u.coldChainTemp() < 2 || u.coldChainTemp() > 8)) {
                    createRisk(visit, "冷链温度异常", RiskSeverity.HIGH, me,
                            "耗材[" + m.getName() + "]交接温度 " + u.coldChainTemp()
                                    + "℃ 超出 2-8℃ 冷链要求，存在药品失效风险");
                }
            }
        }

        // 现场照片绑定
        if (cmd.photoPaths() != null) {
            for (String path : cmd.photoPaths()) {
                VisitPhoto p = new VisitPhoto();
                p.setVisit(visit);
                p.setFilePath(path);
                String type = "other";
                String lower = path.toLowerCase();
                if (lower.contains("ulcer")) type = "ulcer";
                else if (lower.contains("dressing")) type = "dressing";
                else if (lower.contains("sign")) type = "signature";
                else if (lower.contains("scene") || lower.contains("home")) type = "scene";
                p.setPhotoType(type);
                p.setCaption(path);
                photoRepository.save(p);
            }
        }

        // 生命体征自动风险预警
        detectVitalRisks(visit, me);

        auditService.log(me, "巡诊任务", "提交巡诊记录", visit.getId(), visit.getVisitNo(),
                visit.getPatient().getId(),
                "血压=" + nz(visit.getSystolicPressure()) + "/" + nz(visit.getDiastolicPressure())
                        + "，血糖=" + nz(visit.getBloodGlucose())
                        + "，护理项目 " + (cmd.careResults() == null ? 0 : cmd.careResults().size())
                        + " 项；家属签字=" + visit.getFamilySignName());
        return visit;
    }

    private String nz(Object o) { return o == null ? "-" : o.toString(); }

    private void detectVitalRisks(Visit visit, LoginUser me) {
        if (visit.getSystolicPressure() != null && visit.getDiastolicPressure() != null) {
            int sys = visit.getSystolicPressure(), dia = visit.getDiastolicPressure();
            if (sys >= 180 || dia >= 110) {
                createRisk(visit, "血压危急值", RiskSeverity.HIGH, me,
                        "血压 " + sys + "/" + dia + " mmHg，达到高血压急症范围，已建议立即联系家庭医生/转诊");
            } else if (sys >= 160 || dia >= 100) {
                createRisk(visit, "血压明显升高", RiskSeverity.MEDIUM, me,
                        "血压 " + sys + "/" + dia + " mmHg，需医生关注并评估调整用药");
            }
        }
        if (visit.getBloodGlucose() != null) {
            int g = visit.getBloodGlucose();
            if (g >= 167) { // ≈9.3mmol/L 以上（按 mg/dL 演示阈值）
                createRisk(visit, "血糖偏高", RiskSeverity.MEDIUM, me,
                        "随机血糖记录值 " + g + "，提示血糖控制不佳，需医生评估");
            }
        }
        if (visit.getOxygenSaturation() != null && visit.getOxygenSaturation() < 92) {
            createRisk(visit, "血氧偏低", RiskSeverity.HIGH, me,
                    "血氧饱和度 " + visit.getOxygenSaturation() + "%，存在缺氧风险，建议转诊评估");
        }
        if (visit.getPressureUlcerNote() != null
                && (visit.getPressureUlcerNote().contains("Ⅲ") || visit.getPressureUlcerNote().contains("III")
                || visit.getPressureUlcerNote().contains("加重") || visit.getPressureUlcerNote().contains("新发"))) {
            createRisk(visit, "压疮加重/新发", RiskSeverity.MEDIUM, me,
                    "巡诊记录提示压疮新发或加重：" + visit.getPressureUlcerNote());
        }
    }

    private RiskEvent createRisk(Visit visit, String type, RiskSeverity severity,
                                 LoginUser me, String desc) {
        RiskEvent e = new RiskEvent();
        e.setRiskNo(ApplicationService.generateNo("FX"));
        e.setPatient(visit.getPatient());
        e.setVisit(visit);
        e.setRiskType(type);
        e.setSeverity(severity);
        e.setDescription(desc);
        e.setStatus(RiskEventStatus.OPEN);
        e.setReporter(userRepository.findById(me.id()).orElse(null));
        e.setReportedAt(LocalDateTime.now());
        return riskEventRepository.save(e);
    }

    @Transactional
    public VisitException reportException(Long visitId, ExceptionType type, String description,
                                          String evidencePath) {
        LoginUser me = SecurityUtils.current();
        Visit visit = get(visitId);
        VisitException ex = new VisitException();
        ex.setVisit(visit);
        ex.setPatient(visit.getPatient());
        ex.setType(type);
        ex.setDescription(description);
        ex.setEvidencePath(evidencePath);
        ex.setStatus(ExceptionStatus.REPORTED);
        ex = exceptionRepository.save(ex);

        // 除“护士上门超时”外，异常默认将任务挂起
        if (type != ExceptionType.NURSE_OVERTIME) {
            visit.setStatus(VisitStatus.EXCEPTION);
            visitRepository.save(visit);
        }
        auditService.log(me, "巡诊异常", "上报异常", ex.getId(), visit.getVisitNo(),
                visit.getPatient().getId(), "异常类型=" + type.label + "；" + description);
        return ex;
    }

    @Transactional
    public VisitException handleException(Long exceptionId, String conclusion,
                                          ExceptionStatus status, Long rescheduleVisitId,
                                          LocalDateTime newTime, Long reassignNurseId) {
        LoginUser me = SecurityUtils.current();
        VisitException ex = exceptionRepository.findById(exceptionId)
                .orElseThrow(() -> new BusinessException(404, "异常不存在"));
        ex.setHandleConclusion(conclusion);
        ex.setStatus(status == null ? ExceptionStatus.CONFIRMED : status);
        ex.setHandler(userRepository.findById(me.id()).orElse(null));
        ex.setHandledAt(LocalDateTime.now());
        ex = exceptionRepository.save(ex);

        Visit visit = ex.getVisit();
        if (newTime != null) {
            visit.setScheduledAt(newTime);
            visit.setStatus(VisitStatus.SCHEDULED);
            visit.setArrangementNote("异常[" + ex.getType().label + "]处理后改期至 " + newTime
                    + "；结论：" + conclusion);
            visitRepository.save(visit);
        } else if (reassignNurseId != null) {
            SysUser n = userRepository.findById(reassignNurseId).orElseThrow();
            visit.setNurse(n);
            visit.setStatus(VisitStatus.SCHEDULED);
            visit.setArrangementNote("异常[" + ex.getType().label + "]处理后改派给 " + n.getName()
                    + "；结论：" + conclusion);
            visitRepository.save(visit);
        } else if (ex.getStatus() == ExceptionStatus.RESOLVED && visit.getStatus() == VisitStatus.EXCEPTION) {
            visit.setStatus(VisitStatus.SCHEDULED);
            visitRepository.save(visit);
        }
        auditService.log(me, "巡诊异常", "处理异常", ex.getId(), visit.getVisitNo(),
                visit.getPatient().getId(), "处理结论：" + conclusion);
        return ex;
    }

    @Transactional(readOnly = true)
    public List<VisitException> exceptions(String status, Long patientId) {
        if (patientId != null) return exceptionRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        if (status != null && !status.isBlank()) {
            return exceptionRepository.findByStatusOrderByCreatedAtDesc(ExceptionStatus.valueOf(status));
        }
        List<VisitException> list = exceptionRepository.findAll();
        list.sort(Comparator.comparing(VisitException::getCreatedAt).reversed());
        return list;
    }
}
