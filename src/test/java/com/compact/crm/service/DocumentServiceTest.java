package com.compact.crm.service;

import com.compact.crm.entity.Document;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.DocumentType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.DocumentRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.DOCUMENT_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ProductRepository productRepository;
    @Mock private DocumentStorageService documentStorageService;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private EmployeeRepository employeeRepository;

    private DocumentService documentService;

    private Role adminRole;
    private Role employeeRole;
    private Employee admin;
    private Employee employee;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        documentService = new DocumentService(
                documentRepository, productRepository, documentStorageService,
                accessControlService, currentUserService);

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        employeeRole = Role.builder().id(3L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).name("Admin").role(adminRole).build();
        employee = Employee.builder().id(2L).name("Employee").role(employeeRole).build();
    }

    private void grant(Role role, String permissionCode, Scope scope) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.of(RolePermission.builder().scope(scope).build()));
    }

    private void deny(Role role, String permissionCode) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.empty());
    }

    @Test
    void upload_admin_storesBytesAndSavesMetadata() throws IOException {

        grant(adminRole, DOCUMENT_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        MockMultipartFile file = new MockMultipartFile(
                "file", "brochure.pdf", "application/pdf", "content".getBytes());

        when(documentStorageService.store(file)).thenReturn("uuid-generated.pdf");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document saved = documentService.upload(file, DocumentType.COMPANY_PROFILE, null);

        assertThat(saved.getFileName()).isEqualTo("brochure.pdf");
        assertThat(saved.getStoredFileName()).isEqualTo("uuid-generated.pdf");
        assertThat(saved.getUploadedByName()).isEqualTo("Admin");
        assertThat(saved.getCategory()).isEqualTo(DocumentType.COMPANY_PROFILE);
    }

    @Test
    void upload_linksProductForBrochureCategory() throws IOException {

        grant(adminRole, DOCUMENT_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        Product product = Product.builder().id(4L).name("Inverter Battery").build();
        when(productRepository.findById(4L)).thenReturn(Optional.of(product));

        MockMultipartFile file = new MockMultipartFile(
                "file", "inverter-brochure.pdf", "application/pdf", "content".getBytes());
        when(documentStorageService.store(file)).thenReturn("uuid.pdf");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document saved = documentService.upload(file, DocumentType.PRODUCT_BROCHURE, 4L);

        assertThat(saved.getProduct()).isEqualTo(product);
    }

    @Test
    void upload_nonAdmin_rejected() throws IOException {

        deny(employeeRole, DOCUMENT_MANAGE);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);

        MockMultipartFile file = new MockMultipartFile(
                "file", "brochure.pdf", "application/pdf", "content".getBytes());

        assertThatThrownBy(() -> documentService.upload(file, DocumentType.GENERAL, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(documentStorageService, never()).store(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void upload_emptyFile_rejected() {

        grant(adminRole, DOCUMENT_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> documentService.upload(emptyFile, DocumentType.GENERAL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deactivate_admin_setsInactive() {

        grant(adminRole, DOCUMENT_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        Document document = Document.builder().id(3L).isActive(true).build();
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        documentService.deactivate(3L);

        assertThat(document.getIsActive()).isFalse();
    }

}
