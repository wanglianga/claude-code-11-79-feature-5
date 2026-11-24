package com.ctrial.web;

import com.ctrial.model.*;
import com.ctrial.model.Enums.BridgeRoute;
import com.ctrial.repo.SiteRepo;
import com.ctrial.service.AuditService;
import com.ctrial.service.DefinitionComparisonService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition-impact comparison workbench API.
 *
 * <p>Role split mirrors the dual-domain rule: local subject-level
 * ({@link DualDefinitionRow}) rows of China-resident sites are readable only
 * by the China analyst or an administrator; the global team reads the
 * aggregate bridge and the published comparison version, never the local rows.
 */
@RestController
@RequestMapping("/api/definition-comparison")
public class DefinitionComparisonController {

    private final DefinitionComparisonService service;
    private final SiteRepo siteRepo;
    private final AuditService audit;

    public DefinitionComparisonController(DefinitionComparisonService service, SiteRepo siteRepo,
                                          AuditService audit) {
        this.service = service;
        this.siteRepo = siteRepo;
        this.audit = audit;
    }

    private String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    // ---------------- registry / baselines / watermarks ----------------

    @GetMapping("/definitions")
    public List<AnalysisDefinition> definitions() {
        return service.definitions();
    }

    @GetMapping("/frozen-reports")
    public List<FrozenMonthlyReport> frozenReports() {
        return service.frozenReports();
    }

    @GetMapping("/watermarks")
    public List<ComparisonWatermark> watermarks() {
        return service.comparisonWatermarks();
    }

    @GetMapping("/endpoint-watermarks")
    public List<LocalEndpointWatermark> endpointWatermarks() {
        return service.endpointWatermarks();
    }

    // ---------------- evaluation runs ----------------

    public record EvaluateRequest(String frozenReportCode, String siteCode, String route, Integer day) {
    }

    @GetMapping("/runs")
    public List<ComparisonRun> runs() {
        return service.runs();
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody EvaluateRequest req, Authentication auth) {
        if (req.frozenReportCode() == null || req.siteCode() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "frozenReportCode and siteCode are required"));
        }
        BridgeRoute route = "COHORT_BRIDGE".equalsIgnoreCase(req.route()) ? BridgeRoute.COHORT_BRIDGE
                : BridgeRoute.DUAL_BRIDGE;
        int day = req.day() == null ? 100 : req.day();
        return ResponseEntity.ok(
                service.evaluateSite(req.frozenReportCode(), req.siteCode(), route, day, actor(auth)));
    }

    /** Local-plane rows: China-resident site rows are never served to a cross-border role. */
    @GetMapping("/runs/{code}/rows")
    public ResponseEntity<?> rows(@PathVariable String code, Authentication auth) {
        ComparisonRun run = service.findRun(code).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        boolean chinaSite = siteRepo.findByCode(run.getSiteCode()).map(Site::isChinaResident).orElse(false);
        if (chinaSite && !hasAnyRole(auth, "ROLE_CN_ANALYST", "ROLE_ADMIN")) {
            audit.record(actor(auth), "LOCAL_ROW_EGRESS_DENIED", "ComparisonRun", code,
                    "Cross-border role requested local-plane rows for China site " + run.getSiteCode()
                            + "; only aggregate bridge counts may leave the local domain.");
            return ResponseEntity.status(403).body(Map.of("error",
                    "Local subject-level rows of a China-resident site cannot leave the local domain. "
                            + "Use the aggregate bridge and the published comparison version instead."));
        }
        return ResponseEntity.ok(service.rowsOfRun(run.getId()));
    }

    // ---------------- published comparison versions ----------------

    public record PublishRequest(List<String> runCodes) {
    }

    @GetMapping("/versions")
    public List<ComparisonVersion> versions() {
        return service.versions();
    }

    @GetMapping("/versions/{code}")
    public ResponseEntity<?> version(@PathVariable String code) {
        ComparisonVersion v = service.findVersion(code).orElse(null);
        if (v == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v);
        m.put("bridgeAggregates", service.aggregatesOfVersion(v.getId()));
        m.put("denominatorContributions", service.contributionsOfVersion(v.getId()));
        m.put("annotations", service.annotations(v.getId()));
        m.put("frozenReport", service.frozenReports().stream()
                .filter(r -> r.getCode().equals(v.getLinkedFrozenReportCode())).findFirst().orElse(null));
        return ResponseEntity.ok(m);
    }

    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestBody PublishRequest req, Authentication auth) {
        if (req.runCodes() == null || req.runCodes().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "runCodes is required"));
        }
        return ResponseEntity.ok(service.publish(req.runCodes(), actor(auth)));
    }

    // ---------------- attribution workbench ----------------

    @PostMapping("/annotations")
    public ResponseEntity<?> annotate(@RequestBody DefinitionComparisonService.AnnotationRequest req,
                                      Authentication auth) {
        if (req == null || req.versionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "versionId is required"));
        }
        return ResponseEntity.ok(service.annotate(req, actor(auth)));
    }

    private boolean hasAnyRole(Authentication auth, String... roles) {
        if (auth == null) {
            return false;
        }
        var granted = auth.getAuthorities().stream().map(Object::toString).toList();
        for (String role : roles) {
            if (granted.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
