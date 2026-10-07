package com.payflow.repository;

import com.payflow.entity.RoutingConfigEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingConfigRepository extends JpaRepository<RoutingConfigEntry, String> {}
