package com.udea.supermercado.repository;

import java.util.List;
import java.util.Optional;

import com.udea.supermercado.model.DetallePedido;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetallePedidoRepository extends JpaRepository<DetallePedido, Long> {

	Optional<DetallePedido> findByPedidoIdAndProductoId(Long pedidoId, Long productoId);

	List<DetallePedido> findByPedidoId(Long pedidoId);

}
