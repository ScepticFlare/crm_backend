package com.compact.crm.repository;

import com.compact.crm.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    Optional<RolePermission> findByRole_IdAndPermission_Code(Long roleId, String permissionCode);
}
