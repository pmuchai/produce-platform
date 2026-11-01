package com.primeroot.platform.inventory;

import java.util.UUID;
import com.primeroot.platform.access.Branch;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "stock_transfers")
public class StockTransfer implements Persistable<UUID> {
	public enum Status { IN_TRANSIT, RECEIVED }
	@Id private UUID id;
	@ManyToOne(optional = false) private Branch source;
	@ManyToOne(optional = false) private Branch destination;
	@Column(nullable = false, length = 120) private String sourceName;
	@Column(nullable = false, length = 120) private String destinationName;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
	private Status status = Status.IN_TRANSIT;
	@ManyToOne private StockOperation receiveOperation;
	@Version private long version;
	@Transient private boolean fresh = true;

	protected StockTransfer() {}

	StockTransfer(StockOperation dispatch, Branch destination) {
		id = dispatch.getId();
		source = dispatch.getBranch();
		this.destination = destination;
		sourceName = source.getName();
		destinationName = destination.getName();
	}

	void receive(StockOperation operation) {
		receiveOperation = operation;
		status = Status.RECEIVED;
	}
	@PostLoad @PostPersist void persisted() { fresh = false; }
	@Override public boolean isNew() { return fresh; }
	@Override public UUID getId() { return id; }
	public Branch getSource() { return source; }
	public Branch getDestination() { return destination; }
	public String getSourceName() { return sourceName; }
	public String getDestinationName() { return destinationName; }
	public Status getStatus() { return status; }
	public StockOperation getReceiveOperation() { return receiveOperation; }
}
