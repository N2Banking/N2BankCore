import com.n2bank.application.command.DepositCommand;
import com.n2bank.application.command.OperationResult;
import com.n2bank.application.command.TransferCommand;
import com.n2bank.bootstrap.BankApplication;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/** Documentation example; writes demo data to the configured database. */
public class LibraryExample {
    public static void main(String[] args) {
        Currency euro = Currency.getInstance("EUR");
        UUID cashId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID revenueId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        try (BankApplication bank = BankApplication.initializeFromEnvironment()) {
            bank.registerSystemAccounts(euro, cashId, revenueId);
            bank.ensureSystemAccounts(euro, "Example bank cash", "Example fee revenue");
            Customer alice = bank.createCustomer(
                new Customer(UUID.randomUUID(), "Alice", CustomerType.PERSON));
            Customer bob = bank.createCustomer(
                new Customer(UUID.randomUUID(), "Bob", CustomerType.PERSON));
            Account aliceAccount = bank.openAccount(new Account(
                UUID.randomUUID(), "Alice EUR", Optional.of(alice), AccountType.LIABILITY, euro));
            Account bobAccount = bank.openAccount(new Account(
                UUID.randomUUID(), "Bob EUR", Optional.of(bob), AccountType.LIABILITY, euro));
            // Create each command once per logical operation; retain the key and
            // inputs for a retry. A retry returns the original entry with
            // OperationResult.replayed() set.
            bank.deposit(new DepositCommand(IdempotencyKey.create(), aliceAccount.accountId(),
                new Money("100.00", euro), "Example funding"));
            OperationResult result = bank.transfer(new TransferCommand(IdempotencyKey.create(),
                aliceAccount.accountId(), bobAccount.accountId(),
                new Money("25.00", euro), "Example transfer"));
            System.out.println("N² Bank Core — library example");
            for (Posting posting : result.journalEntry().postings()) {
                String name = posting.accountId().equals(aliceAccount.accountId()) ? "Alice" : "Bob";
                System.out.printf("%s %s %s %s%n", name, posting.direction(),
                    posting.amount().currency().getCurrencyCode(),
                    posting.amount().amount().toPlainString());
            }
        }
    }
}
