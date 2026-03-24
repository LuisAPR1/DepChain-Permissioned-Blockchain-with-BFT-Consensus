// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title AccessControl
 * @dev Contrato independente para gerir as permissões de transferência na DepChain.
 */
contract AccessControl {
    address public admin;
    
    // Mapeia um endereço para o seu estado de permissão (true = permitido, false = bloqueado)
    mapping(address => bool) private allowedAccounts;

    /**
     * @dev Quem faz o deploy deste contrato fica como admin.
     */
    constructor() {
        admin = msg.sender;
    }

    modifier onlyAdmin() {
        require(msg.sender == admin, "AccessControl: Apenas o admin pode alterar permissoes");
        _;
    }

    /**
     * @dev Permite ao admin dar ou retirar permissão a uma conta.
     */
    function setPermission(address account, bool isAllowed) external onlyAdmin {
        allowedAccounts[account] = isAllowed;
    }

    /**
     * @dev Função chamada pelo ISTCoin para validar se a conta pode transferir.
     */
    function isAllowedToTransfer(address account) external view returns (bool) {
        return allowedAccounts[account];
    }
}