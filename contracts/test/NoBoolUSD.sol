// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// A TOKEN THAT RETURNS NOTHING from transfer and transferFrom, as some widely used stablecoins do (USDT on
/// Ethereum): the audit's test that the policy works with it (specs/audit.md, E1). Anyone may mint — tests only.
contract NoBoolUSD {
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    function mint(address to, uint256 amount) external { balanceOf[to] += amount; }
    function approve(address spender, uint256 amount) external { allowance[msg.sender][spender] = amount; }
    function transfer(address to, uint256 amount) external {
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
    }
    function transferFrom(address from, address to, uint256 amount) external {
        allowance[from][msg.sender] -= amount;
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
    }
}
