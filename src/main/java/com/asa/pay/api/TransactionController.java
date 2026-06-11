package com.asa.pay.api;

import com.asa.pay.api.dto.AuthorizeRequest;
import com.asa.pay.api.dto.AuthorizeResponse;
import com.asa.pay.api.dto.ConfirmRequest;
import com.asa.pay.api.dto.VoidRequest;
import com.asa.pay.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pos/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(@Valid @RequestBody AuthorizeRequest request) {
        return ResponseEntity.ok(service.authorize(request));
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody ConfirmRequest request) {
        service.confirm(request);
    }

    @PostMapping("/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void voidTransaction(@Valid @RequestBody VoidRequest request) {
        service.voidTransaction(request);
    }
}
