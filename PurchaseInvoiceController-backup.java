
package com.smarterp.controller;

import com.smarterp.dto.PurchaseInvoiceRequest;
import com.smarterp.entity.*;
import com.smarterp.repository.*;
import com.smarterp.service.GstCalculationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/purchases")
@PreAuthorize("hasAnyRole('ADMIN', 'HR')")
public class PurchaseInvoiceController {

    private final PurchaseInvoiceRepository purchaseRepository;
    private final PurchaseInvoiceItemRepository purchaseItemRepository;
    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;
    private final GstConfigurationRepository gstConfigurationRepository;
    private final GstCalculationService gstCalculationService;

    public PurchaseInvoiceController(
            PurchaseInvoiceRepository purchaseRepository,
            PurchaseInvoiceItemRepository purchaseItemRepository,
            SupplierRepository supplierRepository,
            ItemRepository itemRepository,
            GstConfigurationRepository gstConfigurationRepository,
            GstCalculationService gstCalculationService) {

        this.purchaseRepository = purchaseRepository;
        this.purchaseItemRepository = purchaseItemRepository;
        this.supplierRepository = supplierRepository;
        this.itemRepository = itemRepository;
        this.gstConfigurationRepository = gstConfigurationRepository;
        this.gstCalculationService = gstCalculationService;
    }

    @GetMapping
    public List<PurchaseInvoice> getAllPurchases() {
        return purchaseRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPurchaseById(
            @PathVariable Long id) {

        PurchaseInvoice invoice = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Purchase invoice not found"));

        List<PurchaseInvoiceItem> items =
                purchaseItemRepository.findByPurchaseInvoiceId(id);

        Map<String, Object> response = new HashMap<>();
        response.put("invoice", invoice);
        response.put("items", items);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/supplier/{supplierId}")
    public List<PurchaseInvoice> getPurchasesBySupplier(
            @PathVariable Long supplierId) {

        if (!supplierRepository.existsById(supplierId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Supplier not found");
        }

        return purchaseRepository.findBySupplierId(supplierId);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> createPurchase(
            @RequestBody PurchaseInvoiceRequest request) {

        if (request == null || request.getSupplierId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Please select a supplier");
        }

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Supplier not found"));

        if (request.getPurchaseDate() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Purchase date is required");
        }

        if (request.getPurchaseDate().isAfter(LocalDate.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Purchase date cannot be in the future");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Add at least one purchase item");
        }

        String paymentStatus = request.getPaymentStatus() == null
                ? "PENDING"
                : request.getPaymentStatus().trim().toUpperCase();

        String paymentMode = request.getPaymentMode() == null
                ? "CASH"
                : request.getPaymentMode().trim().toUpperCase();

        if (!Set.of("PENDING", "PAID", "PARTIAL").contains(paymentStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid payment status");
        }

        if (!Set.of("CASH", "UPI", "CARD", "BANK", "CREDIT")
                .contains(paymentMode)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid payment mode");
        }

        BigDecimal invoiceDiscount = request.getDiscount() == null
                ? BigDecimal.ZERO
                : request.getDiscount();

        if (invoiceDiscount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Discount cannot be negative");
        }

        // Load company GST configuration.
        GstConfiguration gstConfiguration =
                gstConfigurationRepository.findFirstByOrderByIdAsc()
                        .orElse(null);

        boolean gstEnabled = gstConfiguration != null
                && gstConfiguration.isGstEnabled();

        String companyStateCode = gstEnabled
                ? gstConfiguration.getStateCode()
                : null;

        String supplierStateCode = getSupplierStateCode(supplier);

        if (gstEnabled) {
            if (!isValidStateCode(companyStateCode)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Configure a valid company GST state code first");
            }

            if (!isValidStateCode(supplierStateCode)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Please add a valid supplier state code or GSTIN");
            }
        }

        // Prepare purchase lines.
        List<PurchaseInvoiceItem> invoiceItems = new ArrayList<>();
        List<Item> stockItems = new ArrayList<>();

        BigDecimal rawSubtotal = BigDecimal.ZERO;

        for (PurchaseInvoiceRequest.PurchaseInvoiceLineRequest line
                : request.getItems()) {

            if (line == null || line.getItemId() == null
                    || line.getQuantity() == null
                    || line.getRate() == null) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Incomplete purchase item details");
            }

            if (line.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || line.getRate().compareTo(BigDecimal.ZERO) < 0) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid quantity or purchase rate");
            }

            BigDecimal lineDiscount = line.getDiscountPercent() == null
                    ? BigDecimal.ZERO
                    : line.getDiscountPercent();

            if (lineDiscount.compareTo(BigDecimal.ZERO) < 0
                    || lineDiscount.compareTo(new BigDecimal("100")) > 0) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Line discount must be between 0 and 100");
            }

            Item item = itemRepository.findById(line.getItemId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Item not found: " + line.getItemId()));

            BigDecimal gstRate = item.getGstRate() == null
                    ? BigDecimal.ZERO
                    : item.getGstRate();

            if (gstRate.compareTo(BigDecimal.ZERO) < 0
                    || gstRate.compareTo(new BigDecimal("100")) > 0) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid GST rate for item");
            }

            BigDecimal grossAmount =
                    line.getQuantity().multiply(line.getRate());

            BigDecimal discountedAmount = grossAmount.multiply(
                    BigDecimal.ONE.subtract(
                            lineDiscount.divide(
                                    new BigDecimal("100"),
                                    6,
                                    RoundingMode.HALF_UP)));

            BigDecimal taxableAmount = discountedAmount.setScale(
                    2, RoundingMode.HALF_UP);

            PurchaseInvoiceItem invoiceItem = new PurchaseInvoiceItem();
            invoiceItem.setItemId(item.getId());
            invoiceItem.setQuantity(line.getQuantity());
            invoiceItem.setRate(line.getRate());
            invoiceItem.setDiscountPercent(lineDiscount);
            invoiceItem.setGstRate(gstRate);
            invoiceItem.setTaxableAmount(taxableAmount);

            invoiceItems.add(invoiceItem);
            stockItems.add(item);

            rawSubtotal = rawSubtotal.add(taxableAmount);
        }

        if (invoiceDiscount.compareTo(rawSubtotal) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invoice discount cannot exceed taxable subtotal");
        }

        // Create invoice header.
        PurchaseInvoice invoice = new PurchaseInvoice();

        invoice.setPurchaseNumber(
                "PUR-" + System.currentTimeMillis() + "-"
                        + UUID.randomUUID().toString()
                        .substring(0, 4).toUpperCase());

        invoice.setSupplierId(supplier.getId());
        invoice.setPurchaseDate(request.getPurchaseDate());
        invoice.setSupplierBillNumber(request.getSupplierBillNumber());
        invoice.setNotes(request.getNotes());
        invoice.setPaymentStatus(paymentStatus);
        invoice.setPaymentMode(paymentMode);
        invoice.setDiscount(invoiceDiscount);

        if (gstEnabled) {
            invoice.setPlaceOfSupplyStateCode(supplierStateCode);
        }

        BigDecimal finalSubtotal = BigDecimal.ZERO;
        BigDecimal totalGst = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;

        // Allocate invoice discount proportionally before GST.
        BigDecimal remainingDiscount = invoiceDiscount;

        for (int i = 0; i < invoiceItems.size(); i++) {

            PurchaseInvoiceItem invoiceItem = invoiceItems.get(i);

            BigDecimal originalTaxable = invoiceItem.getTaxableAmount();
            BigDecimal allocatedDiscount = BigDecimal.ZERO;

            if (rawSubtotal.compareTo(BigDecimal.ZERO) > 0) {

                if (i == invoiceItems.size() - 1) {
                    allocatedDiscount = remainingDiscount;
                } else {
                    allocatedDiscount = invoiceDiscount
                            .multiply(originalTaxable)
                            .divide(rawSubtotal, 2, RoundingMode.HALF_UP);

                    if (allocatedDiscount.compareTo(remainingDiscount) > 0) {
                        allocatedDiscount = remainingDiscount;
                    }
                }
            }

            remainingDiscount =
                    remainingDiscount.subtract(allocatedDiscount);

            BigDecimal taxableAmount = originalTaxable
                    .subtract(allocatedDiscount)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal cgst = BigDecimal.ZERO;
            BigDecimal sgst = BigDecimal.ZERO;
            BigDecimal igst = BigDecimal.ZERO;
            BigDecimal gstAmount = BigDecimal.ZERO;

            if (gstEnabled && taxableAmount.compareTo(BigDecimal.ZERO) > 0) {

                Map<String, BigDecimal> gstResult =
                        gstCalculationService.calculateGST(
                                taxableAmount,
                                invoiceItem.getGstRate(),
                                companyStateCode,
                                supplierStateCode);

                cgst = toMoney(gstResult.get("cgst"));
                sgst = toMoney(gstResult.get("sgst"));
                igst = toMoney(gstResult.get("igst"));
                gstAmount = toMoney(gstResult.get("totalGST"));
            }

            BigDecimal lineTotal = taxableAmount.add(gstAmount);

            invoiceItem.setTaxableAmount(taxableAmount);
            invoiceItem.setCgstAmount(cgst);
            invoiceItem.setSgstAmount(sgst);
            invoiceItem.setIgstAmount(igst);
            invoiceItem.setGstAmount(gstAmount);
            invoiceItem.setLineTotal(lineTotal);

            finalSubtotal = finalSubtotal.add(taxableAmount);
            totalCgst = totalCgst.add(cgst);
            totalSgst = totalSgst.add(sgst);
            totalIgst = totalIgst.add(igst);
            totalGst = totalGst.add(gstAmount);
        }

        invoice.setSubtotal(finalSubtotal);
        invoice.setCgstAmount(totalCgst);
        invoice.setSgstAmount(totalSgst);
        invoice.setIgstAmount(totalIgst);
        invoice.setGstAmount(totalGst);
        invoice.setGrandTotal(finalSubtotal.add(totalGst));

        PurchaseInvoice savedInvoice = purchaseRepository.save(invoice);

        // Save invoice lines and increase stock.
        for (int i = 0; i < invoiceItems.size(); i++) {

            PurchaseInvoiceItem invoiceItem = invoiceItems.get(i);
            Item item = stockItems.get(i);

            invoiceItem.setPurchaseInvoiceId(savedInvoice.getId());
            purchaseItemRepository.save(invoiceItem);

            BigDecimal currentStock = item.getCurrentStock() == null
                    ? BigDecimal.ZERO
                    : item.getCurrentStock();

            item.setCurrentStock(
                    currentStock.add(invoiceItem.getQuantity()));

            itemRepository.save(item);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Purchase invoice created successfully");
        response.put("invoice", savedInvoice);
        response.put("items", invoiceItems);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String getSupplierStateCode(Supplier supplier) {

        String stateCode = supplier.getStateCode();

        if (isValidStateCode(stateCode)) {
            return stateCode;
        }

        String gstin = supplier.getGstNumber();

        if (gstin != null && gstin.matches("^\\d{2}[A-Z0-9]{13}$")) {
            return gstin.substring(0, 2);
        }

        return null;
    }

    private boolean isValidStateCode(String stateCode) {
        return stateCode != null && stateCode.matches("^\\d{2}$");
    }

    private BigDecimal toMoney(Object value) {

        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        return new BigDecimal(value.toString())
                .setScale(2, RoundingMode.HALF_UP);
    }
}
