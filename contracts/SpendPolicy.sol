// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// The ERC-20 calls the policy makes.
interface IERC20 {
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
}

/// AN AGENT'S ALLOWANCE, ITS HANDS TIED (okay-allowance specs/allowance.md): the money stays in the owner's wallet;
/// the owner gives this contract an ERC-20 allowance and sets the rules; the agent's one door, `pay`, pulls from the
/// owner's wallet straight to a payee only when every rule holds — an allowed payee within its cap and gap, at most
/// `maxPayment`, the period's total within `periodCap`, an invoice paid once, not paused, not expired. Above
/// `approvalAbove` a payment becomes a request the owner approves or rejects. Every decision is an event.
contract SpendPolicy {
    error NotOwner();
    error NotAgent();
    error Paused();
    error Expired();
    error PayeeNotAllowed(address payee);
    error OverMax(uint256 amount, uint256 maxPayment);
    error PayeeCap(address payee, uint256 wouldBe, uint256 cap);
    error PeriodCap(uint256 wouldBe, uint256 cap);
    error TooSoon(address payee, uint256 nextAt);
    error AlreadyPaid(bytes32 invoice);
    error NoSuchRequest(uint256 id);
    error ZeroAmount();
    error TransferFailed();
    error BadPeriod();

    struct Payee {
        bool allowed;
        uint256 capPerPeriod; // the most this payee may get in one period
        uint256 minGap; // the least time between two payments to it (seconds)
        uint256 spent; // paid to it in the current period
        uint256 spentPeriod; // the period `spent` belongs to
        uint256 lastPaid; // when it was last paid (0: never)
    }

    struct Request {
        address payee;
        uint256 amount;
        bytes32 invoice;
        uint8 state; // 1 waiting, 2 approved (paid), 3 rejected
    }

    address public immutable owner;
    IERC20 public immutable token;
    uint256 public immutable start;
    address public agent;
    uint256 public period; // seconds, e.g. 30 days
    uint256 public periodCap;
    uint256 public maxPayment;
    uint256 public approvalAbove;
    uint256 public expiresAt;
    bool public paused;
    uint256 public spent; // all payees, in the current period
    uint256 public spentPeriod;
    mapping(address => Payee) public payees;
    mapping(bytes32 => bool) public paidInvoice;
    Request[] public requests;

    event Paid(address indexed payee, uint256 amount, bytes32 indexed invoice, bool approvedByOwner);
    event Requested(uint256 indexed id, address indexed payee, uint256 amount, bytes32 indexed invoice);
    event Rejected(uint256 indexed id);
    event PayeeSet(address indexed payee, bool allowed, uint256 capPerPeriod, uint256 minGap);
    event LimitsSet(uint256 period, uint256 periodCap, uint256 maxPayment, uint256 approvalAbove, uint256 expiresAt);
    event AgentSet(address indexed agent);
    event PausedSet(bool paused);

    constructor(IERC20 token_, address agent_, uint256 period_, uint256 periodCap_, uint256 maxPayment_,
        uint256 approvalAbove_, uint256 expiresAt_) {
        if (period_ == 0) revert BadPeriod();
        owner = msg.sender;
        token = token_;
        agent = agent_;
        start = block.timestamp;
        period = period_;
        periodCap = periodCap_;
        maxPayment = maxPayment_;
        approvalAbove = approvalAbove_;
        expiresAt = expiresAt_;
        emit AgentSet(agent_);
        emit LimitsSet(period_, periodCap_, maxPayment_, approvalAbove_, expiresAt_);
    }

    modifier onlyOwner() {
        if (msg.sender != owner) revert NotOwner();
        _;
    }

    /// the number of the current period (fixed windows from the policy's start)
    function currentPeriod() public view returns (uint256) {
        return (block.timestamp - start) / period;
    }

    /// THE AGENT'S ONE DOOR: paid now when every rule holds and the amount is at most `approvalAbove`; above it, a
    /// request for the owner (returns its id + 1; 0 when paid)
    function pay(address payee, uint256 amount, bytes32 invoice) external returns (uint256) {
        if (msg.sender != agent) revert NotAgent();
        check(payee, amount, invoice);
        if (amount > approvalAbove) {
            requests.push(Request(payee, amount, invoice, 1));
            emit Requested(requests.length - 1, payee, amount, invoice);
            return requests.length;
        }
        settle(payee, amount, invoice, false);
        return 0;
    }

    /// the owner approves a waiting request: paid if the rules still hold (the caps, the gap, the invoice once)
    function approve(uint256 id) external onlyOwner {
        if (id >= requests.length || requests[id].state != 1) revert NoSuchRequest(id);
        Request storage r = requests[id];
        check(r.payee, r.amount, r.invoice);
        r.state = 2;
        settle(r.payee, r.amount, r.invoice, true);
    }

    function reject(uint256 id) external onlyOwner {
        if (id >= requests.length || requests[id].state != 1) revert NoSuchRequest(id);
        requests[id].state = 3;
        emit Rejected(id);
    }

    /// every rule but the approval threshold
    function check(address payee, uint256 amount, bytes32 invoice) internal view {
        if (paused) revert Paused();
        if (block.timestamp >= expiresAt) revert Expired();
        if (amount == 0) revert ZeroAmount();
        Payee storage p = payees[payee];
        if (!p.allowed) revert PayeeNotAllowed(payee);
        if (amount > maxPayment) revert OverMax(amount, maxPayment);
        if (paidInvoice[invoice]) revert AlreadyPaid(invoice);
        if (p.lastPaid != 0 && block.timestamp < p.lastPaid + p.minGap) revert TooSoon(payee, p.lastPaid + p.minGap);
        uint256 now_ = currentPeriod();
        uint256 payeeSpent = (p.spentPeriod == now_ ? p.spent : 0) + amount;
        if (payeeSpent > p.capPerPeriod) revert PayeeCap(payee, payeeSpent, p.capPerPeriod);
        uint256 total = (spentPeriod == now_ ? spent : 0) + amount;
        if (total > periodCap) revert PeriodCap(total, periodCap);
    }

    /// the books first, then the transfer from the owner's wallet (checks, effects, interactions)
    function settle(address payee, uint256 amount, bytes32 invoice, bool approvedByOwner) internal {
        uint256 now_ = currentPeriod();
        Payee storage p = payees[payee];
        p.spent = (p.spentPeriod == now_ ? p.spent : 0) + amount;
        p.spentPeriod = now_;
        p.lastPaid = block.timestamp;
        spent = (spentPeriod == now_ ? spent : 0) + amount;
        spentPeriod = now_;
        paidInvoice[invoice] = true;
        if (!token.transferFrom(owner, payee, amount)) revert TransferFailed();
        emit Paid(payee, amount, invoice, approvedByOwner);
    }

    function requestCount() external view returns (uint256) {
        return requests.length;
    }

    // THE OWNER'S: the agent, the payees, the limits, the pause
    function setAgent(address agent_) external onlyOwner {
        agent = agent_;
        emit AgentSet(agent_);
    }

    function setPayee(address payee, bool allowed, uint256 capPerPeriod, uint256 minGap) external onlyOwner {
        Payee storage p = payees[payee];
        p.allowed = allowed;
        p.capPerPeriod = capPerPeriod;
        p.minGap = minGap;
        emit PayeeSet(payee, allowed, capPerPeriod, minGap);
    }

    function setLimits(uint256 period_, uint256 periodCap_, uint256 maxPayment_, uint256 approvalAbove_,
        uint256 expiresAt_) external onlyOwner {
        if (period_ == 0) revert BadPeriod();
        // a new period length would renumber the windows: the spending so far starts a new count
        if (period_ != period) {
            spentPeriod = type(uint256).max;
        }
        period = period_;
        periodCap = periodCap_;
        maxPayment = maxPayment_;
        approvalAbove = approvalAbove_;
        expiresAt = expiresAt_;
        emit LimitsSet(period_, periodCap_, maxPayment_, approvalAbove_, expiresAt_);
    }

    function setPaused(bool paused_) external onlyOwner {
        paused = paused_;
        emit PausedSet(paused_);
    }
}
