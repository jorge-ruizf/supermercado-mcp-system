package com.udea.supermercado.repository;

import java.util.Optional;

import com.udea.supermercado.model.Pedido;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

	@EntityGraph(attributePaths = "detalles")
	@Query("select p from Pedido p where p.id = :id")
	Optional<Pedido> findByIdWithDetalles(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Pedido p where p.id = :id")
	Optional<Pedido> findByIdForUpdate(@Param("id") Long id);

}
