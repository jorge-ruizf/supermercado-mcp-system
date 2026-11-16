package com.udea.supermercado.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "pedidos")
public class Pedido {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "cliente_nombre", nullable = false, length = 255)
	private String clienteNombre;

	@Column(name = "direccion_entrega", nullable = false, length = 255)
	private String direccionEntrega;

	@Column(name = "telefono", nullable = false, length = 255)
	private String telefono;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 20)
	private EstadoPedido estado;

	@Column(name = "total", nullable = false, precision = 12, scale = 2)
	private BigDecimal total;

	@Column(name = "fecha_creacion", nullable = false)
	private LocalDateTime fechaCreacion;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id ASC")
	private List<DetallePedido> detalles = new ArrayList<>();

	public Pedido() {
	}

	public Pedido(String clienteNombre, String direccionEntrega, String telefono) {
		this.clienteNombre = clienteNombre;
		this.direccionEntrega = direccionEntrega;
		this.telefono = telefono;
		this.estado = EstadoPedido.PENDIENTE;
		this.total = BigDecimal.ZERO.setScale(2);
		this.fechaCreacion = LocalDateTime.now();
	}

	public Long getId() {
		return this.id;
	}

	public String getClienteNombre() {
		return this.clienteNombre;
	}

	public String getDireccionEntrega() {
		return this.direccionEntrega;
	}

	public String getTelefono() {
		return this.telefono;
	}

	public EstadoPedido getEstado() {
		return this.estado;
	}

	public void setEstado(EstadoPedido estado) {
		this.estado = estado;
	}

	public BigDecimal getTotal() {
		return this.total;
	}

	public LocalDateTime getFechaCreacion() {
		return this.fechaCreacion;
	}

	public Long getVersion() {
		return this.version;
	}

	public List<DetallePedido> getDetalles() {
		return this.detalles;
	}

	public void agregarDetalle(DetallePedido detalle) {
		detalle.setPedido(this);
		this.detalles.add(detalle);
	}

	public void recalcularTotal() {
		this.total = this.detalles.stream()
				.map(DetallePedido::getSubtotal)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2);
	}

}
