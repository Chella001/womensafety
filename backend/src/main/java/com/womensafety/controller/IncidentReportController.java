package com.womensafety.controller;

import java.util.List;

import com.womensafety.dto.IncidentReportRequest;
import com.womensafety.dto.IncidentReportResponse;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.service.IncidentReportService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incident-reports")
public class IncidentReportController {

    private final IncidentReportService service;

    public IncidentReportController(IncidentReportService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncidentReportResponse> create(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @Valid @RequestBody IncidentReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(IncidentReportResponse.from(service.create(principal.uid(), request)));
    }

    @GetMapping("/me")
    public ResponseEntity<List<IncidentReportResponse>> mine(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listOwn(principal.uid()).stream().map(IncidentReportResponse::from).toList());
    }
}
